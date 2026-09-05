package com.example.audiotester.common

import android.content.Context
import android.util.Log
import org.w3c.dom.Element
import java.io.File
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Unified audio configuration (superset of player + recorder domain fields).
 * An empty audioFilePath is interpreted by the engine: playback → built-in audio source; recording → auto-generated output path.
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
    /** True when audioFilePath is used as-is; empty/asset:// is a sentinel (player: built-in source, recorder: auto-generated path) */
    val hasUsableFilePath: Boolean
        get() = audioFilePath.isNotEmpty() && !audioFilePath.startsWith("asset://")

    init {
        // Upper bound: a huge multiplier would overflow minBufferSize * multiplier at start
        // time; 100 keeps even the largest real minBufferSize far away from Int overflow
        require(bufferMultiplier in 1..100) { "Buffer multiplier out of range 1..100: $bufferMultiplier" }
    }

    companion object {
        private const val TAG = "AudioConfig"
        /** Single source of default values: data class defaults, parseConfigs fallbacks, and getDefaultConfigs are all based on this */
        private val DEFAULT = AudioConfig()

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

        /** Parse failure → fallback defaults (pure JVM stream input, unit-testable) */
        internal fun loadConfigsFromRaw(xml: InputStream, section: String): List<AudioConfig> =
            try {
                parseConfigs(xml, section)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse $section configurations", e)
                getDefaultConfigs(section)
            }

        /** A missing section is treated as a parse failure (loadConfigsFromRaw provides the fallback); an empty section returns an empty list */
        internal fun parseConfigs(xml: InputStream, section: String): List<AudioConfig> {
            val sectionElement = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(xml).documentElement.getElementsByTagName(section).item(0) as Element?
                ?: throw IllegalArgumentException("Missing section: $section")
            val entries = sectionElement.getElementsByTagName("config")
            // A single bad config entry only skips that entry, without dragging the whole section into the emergency fallback
            return (0 until entries.length).mapNotNull { i ->
                runCatching {
                    val c = entries.item(i) as Element
                    AudioConfig(
                        usage = c.childText("usage", DEFAULT.usage),
                        contentType = c.childText("contentType", DEFAULT.contentType),
                        performanceMode = c.childText("performanceMode", DEFAULT.performanceMode),
                        audioSource = c.childText("audioSource", DEFAULT.audioSource),
                        sampleRate = c.childInt("sampleRate", DEFAULT.sampleRate),
                        channelCount = c.childInt("channelCount", DEFAULT.channelCount),
                        audioFormat = c.childInt("audioFormat", DEFAULT.audioFormat),
                        bufferMultiplier = c.childInt("bufferMultiplier", DEFAULT.bufferMultiplier),
                        audioFilePath = c.childText("audioFilePath", DEFAULT.audioFilePath),
                        description = c.childText("description", "Custom configuration")
                    )
                }.onFailure {
                    Log.e(TAG, "Skipping invalid $section config entry #$i", it)
                }.getOrNull()
            }
        }

        internal fun getDefaultConfigs(section: String): List<AudioConfig> = when (section) {
            // All other values come from the data-class defaults (DEFAULT)
            "player" -> listOf(AudioConfig(description = "Emergency Fallback - Media Playback"))
            else -> listOf(AudioConfig(description = "Emergency Fallback - Stereo Recording"))
        }
    }
}

/**
 * XML configuration stream loader: external /data file first, otherwise read from assets
 * (XML natively supports comments, no preprocessing needed).
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

/** Reads child element text: missing OR empty element → default value (an empty <usage></usage>
 *  means "unspecified" and falls back like the old parser did; a typo'd value still fails loudly) */
private fun Element.childText(name: String, default: String): String =
    getElementsByTagName(name).item(0)?.textContent?.trim()?.ifEmpty { null } ?: default

/** Missing or empty element → default; present-but-unparseable text throws — the per-entry
 *  runCatching in parseConfigs then skips the entry loudly (same as an out-of-range
 *  bufferMultiplier) instead of silently testing at the default rate */
private fun Element.childInt(name: String, default: Int): Int =
    getElementsByTagName(name).item(0)?.textContent?.trim()?.ifEmpty { null }?.let {
        it.toIntOrNull() ?: throw NumberFormatException("Invalid $name: \"$it\"")
    } ?: default
