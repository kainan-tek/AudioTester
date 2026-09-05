package com.example.audiotester.player

import android.Manifest
import android.content.Context
import android.os.Build
import com.example.audiotester.common.AudioConfig
import com.example.audiotester.common.AudioConstants
import com.example.audiotester.common.AudioEngine
import com.example.audiotester.common.AudioErrorType
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
    override val configTitle: CharSequence get() = "Playback Configuration"
    override val errorDialogTitle: CharSequence get() = "Playback Error"

    override fun createEngine(context: Context): AudioEngine = AudioPlayer(context)

    private fun storagePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    /** Built-in audio sources (sentinel path, see AudioConfig.hasUsableFilePath) read from assets and need no storage
     *  permission; skip the permission gate. While configs are still loading (null) the effective config is unknown —
     *  ask conservatively: a denied request costs nothing, but skipping a needed one makes start fail as a bogus [FILE] */
    override fun permissionsForCurrentConfig(): Array<String> =
        if (viewModel.currentConfig.value?.hasUsableFilePath != false) storagePermissions() else emptyArray()

    override fun formatInfo(config: AudioConfig): String {
        // Mirror the engine's three-way dispatch (empty → default asset, asset:// → that asset,
        // else file) so the panel always names the file actually played
        val path = config.audioFilePath.ifEmpty { AudioConstants.DEFAULT_AUDIO_FILE }
        val fileDisplay = if (path.startsWith("asset://")) "Bundled sample ($path)" else path
        return "Current Config: ${config.description}\n" +
            "Usage: ${config.usage} | ${config.contentType}\n" +
            "Mode: ${config.performanceMode}\n" +
            "File: $fileDisplay"
    }

    override fun friendlyMessage(type: AudioErrorType): String = when (type) {
        AudioErrorType.FILE ->
            "Unable to open audio file. The file may be corrupted or inaccessible."
        AudioErrorType.TRUNCATED ->
            "The audio file is incomplete: its content ended before the declared size, so playback stopped early."
        AudioErrorType.STREAM ->
            "Audio system initialization failed. Please try again."
        AudioErrorType.PERMISSION ->
            "Audio file access permission is required. Please grant the permission in Settings."
        AudioErrorType.PARAM ->
            "Invalid audio configuration. Please select a different configuration."
        AudioErrorType.FOCUS ->
            "Unable to play audio. Another app may be using the audio system."
        AudioErrorType.ALREADY_ACTIVE ->
            "Playback is already in progress."
        // The player never emits FINALIZE; kept for exhaustiveness
        AudioErrorType.FINALIZE -> "Playback failed. Please try again."
    }
}
