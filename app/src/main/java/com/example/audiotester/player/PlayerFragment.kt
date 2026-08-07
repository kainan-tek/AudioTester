package com.example.audiotester.player

import android.Manifest
import android.content.Context
import android.os.Build
import com.example.audiotester.common.AudioConfig
import com.example.audiotester.common.AudioEngine
import com.example.audiotester.common.AudioMessages
import com.example.audiotester.common.AudioTestFragment

class PlayerFragment : AudioTestFragment() {

    override val section: String = "player"
    override val messages: AudioMessages = AudioMessages(
        ready = "Ready to play",
        preparing = "Preparing to Play...",
        active = "Playing...",
        stopped = "Playback Stopped",
        failed = "Playback failed",
    )
    override val startButtonText: CharSequence get() = "Start\nPlayback"
    override val stopButtonText: CharSequence get() = "Stop\nPlayback"
    override val configTitle: CharSequence get() = "Playback Configuration"
    override val errorDialogTitle: CharSequence get() = "Playback Error"

    override fun createEngine(context: Context): AudioEngine = AudioPlayer(context)

    override fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    override fun formatInfo(config: AudioConfig): String =
        "Current Config: ${config.description}\n" +
            "Usage: ${config.usage} | ${config.contentType}\n" +
            "Mode: ${config.performanceMode} | ${config.transferMode}\n" +
            "File: ${config.audioFilePath.ifEmpty { "Bundled sample (asset://sample/48k_2ch_16bit.wav)" }}"

    override fun friendlyErrorMessage(raw: String): String = when {
        raw.startsWith("[FILE]", ignoreCase = true) ->
            "Unable to open audio file. The file may be corrupted or inaccessible."
        raw.startsWith("[STREAM]", ignoreCase = true) ->
            "Audio system initialization failed. Please try again."
        raw.startsWith("[PERMISSION]", ignoreCase = true) ->
            "Audio file access permission is required. Please grant the permission in Settings."
        raw.startsWith("[PARAM]", ignoreCase = true) ->
            "Invalid audio configuration. Please select a different configuration."
        raw.startsWith("[FOCUS]", ignoreCase = true) ->
            "Unable to play audio. Another app may be using the audio system."
        raw.contains("Already playing", ignoreCase = true) ->
            "Playback is already in progress."
        else -> "Playback failed. Please try again."
    }
}
