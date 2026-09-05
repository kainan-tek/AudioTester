package com.example.audiotester.common

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaRecorder

/**
 * Error families surfaced through [AudioEngine.Listener.onError]. Fragments map these to
 * user-facing text (exhaustive when — a new type fails compilation until every feature
 * translates it); human-readable detail stays engine-side (logcat) and never reaches the UI.
 */
enum class AudioErrorType {
    FILE, STREAM, PERMISSION, PARAM, FOCUS, FINALIZE, TRUNCATED, ALREADY_ACTIVE
}

/**
 * Audio constants (player domain + recorder domain combined)
 */
object AudioConstants {

    // Merged config file (player and recorder share a single file with two sections: "player" / "recorder")
    const val CONFIG_FILE_PATH = "/data/audio_configs.xml"
    const val ASSETS_CONFIG_FILE = "audio_configs.xml"
    const val DEFAULT_AUDIO_FILE = "asset://sample/48k_2ch_16bit.wav"

    // ===== Player domain =====

    /** AudioTrack usage constant map */
    object Usage {
        // ---- System usages (1000-1004) ----
        // setUsage() only accepts SDK usages; passing 1000-1004 always throws IAE. The official
        // entry is @SystemApi Builder.setSystemUsage() (MODIFY_AUDIO_ROUTING + system deployment)
        // — see the reflection path in buildAudioAttributes(); on normal installs the call fails,
        // the same convention as other system-only configs. USAGE_SPEAKER_CLEANUP(1004) is further
        // gated by android.media.audio.speaker_cleanup_usage. Values per AOSP android-16.0.0_r4
        // (SYSTEM_USAGE_OFFSET = 1000). For native AAOS testing: AAudioTester's
        // AAudioStreamBuilder_setUsage supports them directly.

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

    /** Raw value resolution including system usages (>= 1000 means system usage) */
    fun resolveUsage(usage: String): Int =
        parseEnumValue(ALL_USAGE_MAP, usage, "Usage")

    /** True for system usages (1000-1004): vehicle-only, need MODIFY_AUDIO_ROUTING + system deployment */
    fun isSystemUsage(usage: String): Boolean = resolveUsage(usage) >= SYSTEM_USAGE_START

    /** First unknown player-domain enum string ("usage: XXX"), or null if all resolve — a fallback would silently test the wrong attributes */
    fun findUnknownPlayerEnum(usage: String, contentType: String, performanceMode: String): String? = when {
        usage !in ALL_USAGE_MAP -> "usage: $usage"
        contentType !in ContentType.MAP -> "contentType: $contentType"
        performanceMode !in PerformanceMode.MAP -> "performanceMode: $performanceMode"
        else -> null
    }

    /** System usage start value (matches @hide AudioAttributes.SYSTEM_USAGE_OFFSET) */
    private const val SYSTEM_USAGE_START = 1000

    private val ALL_USAGE_MAP: Map<String, Int> = Usage.MAP + Usage.SYSTEM_MAP

    /**
     * Builds player-domain AudioAttributes (single entry point). usage < 1000 goes through
     * setUsage(); system usages (>= 1000) via reflection on @SystemApi setSystemUsage() — the
     * two cannot be mixed (build() throws IAE). Failure on normal installs is expected:
     * system usage needs MODIFY_AUDIO_ROUTING + system deployment.
     */
    fun buildAudioAttributes(usage: String, contentType: String): AudioAttributes {
        val builder = AudioAttributes.Builder()
            .setContentType(getContentType(contentType))
        builder.applyUsage(resolveUsage(usage))
        return builder.build()
    }

    private fun AudioAttributes.Builder.applyUsage(usage: Int) {
        if (usage < SYSTEM_USAGE_START) setUsage(usage) else setSystemUsageReflectively(usage)
    }

    private val setSystemUsageMethod by lazy {
        AudioAttributes.Builder::class.java
            .getMethod("setSystemUsage", Int::class.javaPrimitiveType)
    }

    /** Reflectively invokes @SystemApi AudioAttributes.Builder.setSystemUsage(int) (not in the public SDK) */
    private fun AudioAttributes.Builder.setSystemUsageReflectively(usage: Int) {
        try {
            setSystemUsageMethod.invoke(this, usage)
        } catch (e: Throwable) {
            // Normalize into a handleable error: hidden-API interception throws NoSuchMethodError
            // (an Error, unhandled by the engine's catch(Exception)); missing permission throws IAE
            throw IllegalArgumentException(
                "setSystemUsage failed for usage $usage (requires MODIFY_AUDIO_ROUTING + system deployment)",
                e
            )
        }
    }

    fun getContentType(contentType: String): Int = parseEnumValue(
        ContentType.MAP, contentType, "ContentType"
    )

    fun getPerformanceMode(performanceMode: String): Int = parseEnumValue(
        PerformanceMode.MAP, performanceMode, "PerformanceMode"
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
        parseEnumValue(AudioSource.MAP, audioSource, "AudioSource")

    /** Unknown recorder-domain enum string ("audioSource: XXX"), or null — same contract as [findUnknownPlayerEnum] */
    fun findUnknownRecorderEnum(audioSource: String): String? =
        if (audioSource in AudioSource.MAP) null else "audioSource: $audioSource"

    // ===== Shared helpers =====

    /** Callers pre-validate via findUnknown*: this fails loudly instead of silently substituting a default constant */
    private fun parseEnumValue(
        map: Map<String, Int>,
        value: String,
        typeName: String,
    ): Int = requireNotNull(map[value]) { "Unknown $typeName value: $value" }

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
        requireNotNull(BIT_DEPTH_FORMATS[bitsPerSample]) { "Unsupported bit depth: $bitsPerSample" }

    fun getOutputChannelMask(channelCount: Int): Int =
        requireNotNull(OUTPUT_CHANNEL_MASKS[channelCount]) { "Unsupported output channel count: $channelCount" }

    fun getInputChannelMask(channelCount: Int): Int =
        requireNotNull(INPUT_CHANNEL_MASKS[channelCount]) { "Unsupported input channel count: $channelCount" }

    fun isValidSampleRate(rate: Int): Boolean = rate in 8000..192000

    // Valid channel counts share the mask tables: counts without a mask (e.g. input 4/6, output
    // 3/5/7) must not silently fall back to stereo while the header keeps the original count —
    // that misaligns the data.
    fun isValidInputChannelCount(count: Int): Boolean = count in INPUT_CHANNEL_MASKS

    fun isValidOutputChannelCount(count: Int): Boolean = count in OUTPUT_CHANNEL_MASKS

    fun isValidBitDepth(depth: Int): Boolean = depth in BIT_DEPTH_FORMATS
}
