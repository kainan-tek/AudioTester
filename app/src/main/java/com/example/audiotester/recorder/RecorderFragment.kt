package com.example.audiotester.recorder

import android.Manifest
import android.content.Context
import android.os.Build
import com.example.audiotester.common.AudioConfig
import com.example.audiotester.common.AudioEngine
import com.example.audiotester.common.AudioMessages
import com.example.audiotester.common.AudioTestFragment

class RecorderFragment : AudioTestFragment() {

    override val section: String = "recorder"
    override val messages: AudioMessages = AudioMessages(
        ready = "Ready to record",
        preparing = "Preparing...",
        active = "Recording...",
        stopped = "Recording Stopped",
        failed = "Recording Failed",
    )
    override val startButtonText: CharSequence get() = "Start\nRecord"
    override val stopButtonText: CharSequence get() = "Stop\nRecord"
    override val configTitle: CharSequence get() = "Recording Configuration"
    override val errorDialogTitle: CharSequence get() = "Recording Error"

    override fun createEngine(context: Context): AudioEngine = AudioRecorder(context)

    @Suppress("ObsoleteSdkInt")
    override fun requiredPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.P ->
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2 ->
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_EXTERNAL_STORAGE)
        else -> arrayOf(Manifest.permission.RECORD_AUDIO)
    }

    override fun formatInfo(config: AudioConfig): String {
        val filePathDisplay = config.audioFilePath.ifBlank {
            "<App default path (auto-generated at recording start)>"
        }
        return "Current Config: ${config.description}\n" +
            "Source: ${config.audioSource}\n" +
            "Parameters: ${config.sampleRate}Hz | ${config.channelCount}ch | ${config.audioFormat}bit\n" +
            "File: $filePathDisplay"
    }

    override fun friendlyErrorMessage(raw: String): String = when {
        raw.startsWith("[FILE]", ignoreCase = true) ->
            "Unable to create recording file. Please check storage permissions and available space."
        raw.startsWith("[STREAM]", ignoreCase = true) ->
            "Audio system initialization failed. Please try again."
        raw.startsWith("[PERMISSION]", ignoreCase = true) ->
            "Microphone access permission is required. Please grant the permission in Settings."
        raw.startsWith("[PARAM]", ignoreCase = true) ->
            "Invalid audio configuration. Please select a different configuration."
        raw.contains("Already recording", ignoreCase = true) -> "Recording is already in progress."
        raw.contains("Not currently recording", ignoreCase = true) -> "No recording is in progress."
        else -> "Recording failed. Please try again."
    }
}
