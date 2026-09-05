package com.example.audiotester.recorder

import android.Manifest
import android.content.Context
import com.example.audiotester.common.AudioConfig
import com.example.audiotester.common.AudioEngine
import com.example.audiotester.common.AudioErrorType
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
    override val configTitle: CharSequence get() = "Recording Configuration"
    override val errorDialogTitle: CharSequence get() = "Recording Error"

    override fun createEngine(context: Context): AudioEngine = AudioRecorder(context)

    // Recording only ever writes to app-scoped dirs (getExternalFilesDir / filesDir) — no
    // storage permission is needed on any supported API level; RECORD_AUDIO is the sole gate
    override fun permissionsForCurrentConfig(): Array<String> = arrayOf(Manifest.permission.RECORD_AUDIO)

    override fun formatInfo(config: AudioConfig): String {
        val filePathDisplay = if (config.hasUsableFilePath) config.audioFilePath
            else "<App default path (auto-generated at recording start)>"
        return "Current Config: ${config.description}\n" +
            "Source: ${config.audioSource}\n" +
            "Parameters: ${config.sampleRate}Hz | ${config.channelCount}ch | ${config.audioFormat}bit\n" +
            "File: $filePathDisplay"
    }

    override fun friendlyMessage(type: AudioErrorType): String = when (type) {
        AudioErrorType.FILE ->
            "Unable to create recording file. Please check storage permissions and available space."
        AudioErrorType.FINALIZE ->
            "Recording data was saved but the file could not be finalized. The file may be unreadable. Please check storage."
        AudioErrorType.STREAM ->
            "Audio system initialization failed. Please try again."
        AudioErrorType.PERMISSION ->
            "Microphone access permission is required. Please grant the permission in Settings."
        AudioErrorType.PARAM ->
            "Invalid audio configuration. Please select a different configuration."
        AudioErrorType.ALREADY_ACTIVE ->
            "Recording is already in progress."
        // The recorder never emits TRUNCATED / FOCUS; kept for exhaustiveness
        AudioErrorType.TRUNCATED -> "Recording failed. Please try again."
        AudioErrorType.FOCUS -> "Recording failed. Please try again."
    }
}
