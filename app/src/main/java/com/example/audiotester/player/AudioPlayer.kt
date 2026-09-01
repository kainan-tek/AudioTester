package com.example.audiotester.player

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import com.example.audiotester.common.AudioConstants
import com.example.audiotester.common.AudioEngineBase
import com.example.audiotester.common.AudioState
import com.example.audiotester.common.WavFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.Locale

/**
 * Audio player based on the AudioTrack API.
 */
class AudioPlayer(private val context: Context) : AudioEngineBase() {

    companion object {
        private const val TAG = "AudioPlayer"
    }

    override val tag: String get() = TAG

    private var audioTrack: AudioTrack? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var wavFile: WavFile? = null

    override val alreadyActiveMessage = "Already playing"
    override val permissionDeniedMessage = "Permission denied"
    override val startupFailedMessage = "Playback initialization failed"
    override val startedMessage = "Playback started successfully"

    override fun releaseAudioResources() {
        try {
            audioTrack?.apply {
                if (this.state == AudioTrack.STATE_INITIALIZED) stop()
                release()
            }
            audioTrack = null

            abandonAudioFocus()
            audioManager = null

            wavFile?.close()
            wavFile = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing resources", e)
        }
    }

    /**
     * Opens the audio file. Empty path → built-in source; asset:// prefix → assets; otherwise → regular file.
     */
    override fun openResources(): Boolean {
        val path = currentConfig.audioFilePath.ifEmpty { AudioConstants.DEFAULT_AUDIO_FILE }
        wavFile = WavFile(path)
        val opened = if (path.startsWith("asset://")) {
            try {
                wavFile!!.open(context.assets.open(path.removePrefix("asset://")))
            } catch (_: IOException) {
                handleError("${AudioConstants.ErrorTypes.FILE} Cannot open audio asset: $path")
                return false
            }
        } else {
            wavFile!!.open()
        }

        if (!opened || !wavFile!!.isValid()) {
            handleError("${AudioConstants.ErrorTypes.FILE} Cannot open audio file: $path")
            return false
        }

        Log.d(
            TAG,
            "Audio file opened: ${wavFile!!.sampleRate}Hz, ${wavFile!!.bitsPerSample}bit, ${wavFile!!.channelCount}ch"
        )
        return true
    }

    override fun initializeAudio(): Boolean {
        val wavFile = wavFile ?: return false

        try {
            if (!requestAudioFocus()) {
                handleError("${AudioConstants.ErrorTypes.FOCUS} Cannot obtain audio focus")
                return false
            }

            if (!validateAudioParameters(wavFile)) {
                abandonAudioFocus()
                return false
            }

            val channelMask = AudioConstants.getOutputChannelMask(wavFile.channelCount)
            val audioFormat = AudioConstants.getFormatFromBitDepth(wavFile.bitsPerSample)
            val minBufferSize = AudioTrack.getMinBufferSize(wavFile.sampleRate, channelMask, audioFormat)
            Log.i(TAG, "getMinBufferSize: $minBufferSize bytes")

            if (minBufferSize == AudioTrack.ERROR_BAD_VALUE) {
                abandonAudioFocus()
                handleError("${AudioConstants.ErrorTypes.PARAM} Unsupported audio parameter combination: ${wavFile.sampleRate}Hz, ${wavFile.channelCount}ch, ${wavFile.bitsPerSample}bit")
                return false
            }

            val bufferSize = minBufferSize * currentConfig.bufferMultiplier

            val audioAttributes =
                AudioConstants.buildAudioAttributes(currentConfig.usage, currentConfig.contentType)

            audioTrack = AudioTrack.Builder().setAudioAttributes(audioAttributes).setAudioFormat(
                AudioFormat.Builder().setSampleRate(wavFile.sampleRate).setChannelMask(channelMask)
                    .setEncoding(audioFormat).build()
            ).setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioConstants.getPerformanceMode(currentConfig.performanceMode))
                .build()

            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                abandonAudioFocus()
                handleError("${AudioConstants.ErrorTypes.STREAM} AudioTrack initialization failed, state: ${audioTrack?.state}")
                return false
            }

            Log.i(
                TAG,
                "AudioTrack initialized - ${wavFile.sampleRate}Hz, ${wavFile.channelDescription}, ${wavFile.bitsPerSample}bit"
            )

            if (wavFile.channelCount >= 10) {
                Log.i(TAG, "3D audio information:")
                Log.i(TAG, "Channel layout: ${wavFile.channelLayout}")
                if (wavFile.channelCount == 12) {
                    Log.i(TAG, "7.1.4 format: includes 4 height channels (Ltf Rtf Ltb Rtb)")
                }
            }

            return true
        } catch (e: Exception) {
            abandonAudioFocus()
            handleError("${AudioConstants.ErrorTypes.STREAM} AudioTrack creation failed: ${e.message}")
            return false
        }
    }

    private fun validateAudioParameters(wavFile: WavFile): Boolean {
        if (!AudioConstants.isValidSampleRate(wavFile.sampleRate)) {
            handleError("${AudioConstants.ErrorTypes.PARAM} Unsupported sample rate: ${wavFile.sampleRate}Hz (supported range: 8000-192000Hz)")
            return false
        }
        if (!AudioConstants.isValidOutputChannelCount(wavFile.channelCount)) {
            handleError("${AudioConstants.ErrorTypes.PARAM} Unsupported channel count: ${wavFile.channelCount} (supported: 1/2/4/6/8/10/12/16)")
            return false
        }
        return true
    }

    private fun requestAudioFocus(): Boolean {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // System usages (>= 1000) are vehicle-critical audio and do not rely on regular focus
        // management, so skip the focus request.
        // (SDK usages are always < 1000; this check only affects Usage.SYSTEM_MAP configs)
        if (AudioConstants.resolveUsage(currentConfig.usage) >= 1000) return true

        val focusType = determineFocusType()

        val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            handleFocusChange(focusChange)
        }

        val audioAttributes =
            AudioConstants.buildAudioAttributes(currentConfig.usage, currentConfig.contentType)

        val request = AudioFocusRequest.Builder(focusType).setAudioAttributes(audioAttributes)
            .setOnAudioFocusChangeListener(focusChangeListener).setWillPauseWhenDucked(false)
            .setAcceptsDelayedFocusGain(false).build()

        val result = audioManager?.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (result) audioFocusRequest = request

        return result
    }

    private fun determineFocusType(): Int {
        val usage = currentConfig.usage
        return when {
            usage.contains("NAVIGATION") ->
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            usage.contains("VOICE_COMMUNICATION") ->
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            else -> AudioManager.AUDIOFOCUS_GAIN
        }
    }

    /**
     * The UI has no pause support, so every focus loss is turned into a stop
     */
    private fun handleFocusChange(focusChange: Int) {
        if (focusChange in listOf(
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
            )
        ) {
            Log.d(TAG, "Audio focus lost (type: $focusChange), stopping playback")
            stop()
        }
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let { request ->
            audioManager?.abandonAudioFocusRequest(request)
            audioFocusRequest = null
        }
    }

    override fun startLoop() {
        loopJob = loopScope.launch {
            val wavFile = wavFile ?: return@launch
            val audioTrack = audioTrack ?: return@launch

            val bytesPerFrame = wavFile.blockAlign
            val audioTrackBufferSize = audioTrack.bufferSizeInFrames * bytesPerFrame
            val rawWriteBufferSize =
                when (AudioConstants.getPerformanceMode(currentConfig.performanceMode)) {
                    AudioTrack.PERFORMANCE_MODE_LOW_LATENCY -> audioTrackBufferSize / 4
                    AudioTrack.PERFORMANCE_MODE_POWER_SAVING -> audioTrackBufferSize / 2
                    else -> audioTrackBufferSize / 3
                }
            // Round down to a whole number of frames: write() only consumes whole frames
            // (userSize >= mFrameSize loop); a leftover partial frame can never be written and
            // returns 0; non-frame-aligned blocks would drop bytes every block and shift all
            // subsequent data into noise
            val writeBufferSize = rawWriteBufferSize / bytesPerFrame * bytesPerFrame

            val buffer = ByteArray(writeBufferSize)
            var totalBytes = 0L
            var lastLoggedBytes = 0L

            try {
                audioTrack.play()

                var readLoopEnded = false
                while (isActive && state == AudioState.ACTIVE) {
                    val bytesRead = wavFile.readData(buffer, 0, buffer.size)
                    if (bytesRead <= 0) {
                        Log.d(TAG, "File reading completed")
                        readLoopEnded = true
                        break
                    }

                    // Drop a partial trailing frame (malformed data chunk / truncated file):
                    // readData fills the buffer fully on all earlier blocks, so this is a
                    // last-block-only case; write() could never consume the leftover partial frame
                    val alignedBytes = bytesRead - bytesRead % bytesPerFrame
                    if (alignedBytes == 0) {
                        readLoopEnded = true
                        break
                    }

                    // The blocking write()'s internal drain loop either consumes all whole
                    // frames or errors out (partial success is always followed by an error
                    // code). Treat a short write as an exception — fail loudly to expose platform issues
                    val bytesWritten = audioTrack.write(buffer, 0, alignedBytes)
                    if (bytesWritten != alignedBytes) {
                        throw IOException("AudioTrack write incomplete: $bytesWritten/$alignedBytes")
                    }
                    if (alignedBytes != bytesRead) {
                        readLoopEnded = true   // partial tail = end of data; drain and stop below
                        break
                    }
                    totalBytes += bytesWritten
                    if (totalBytes - lastLoggedBytes >= PROGRESS_LOG_INTERVAL_BYTES) {
                        val mbPlayed = totalBytes / (1024.0 * 1024.0)
                        Log.v(TAG, "Progress: %.1fMB".format(Locale.US, mbPlayed))
                        lastLoggedBytes = totalBytes
                    }
                }

                if (state == AudioState.ACTIVE) {
                    // On a natural end (EOF), stop() would discard the frames still buffered in
                    // the track, cutting off the tail. Poll playbackHeadPosition until drained,
                    // then stop, so the entire audio is heard.
                    if (readLoopEnded) {
                        val framesWritten = totalBytes / bytesPerFrame
                        // Drain time is at most the track buffer duration (on devices with a large
                        // minBufferSize this can be seconds); scale the deadline by the buffer
                        // duration + 2s margin to guard against a stuck HAL; 10ms polling is enough
                        val drainMs = audioTrack.bufferSizeInFrames * 1000L / wavFile.sampleRate
                        val deadline = SystemClock.elapsedRealtime() + drainMs + 2000
                        while (isActive && state == AudioState.ACTIVE &&
                            audioTrack.playbackHeadPosition < framesWritten &&
                            SystemClock.elapsedRealtime() < deadline) {
                            delay(10)
                        }
                    }
                    val mbTotal = totalBytes / (1024.0 * 1024.0)
                    Log.i(TAG, "Playback completed: %.1fMB".format(Locale.US, mbTotal))
                    stop()
                }
            } catch (e: Exception) {
                handleLoopError("${AudioConstants.ErrorTypes.STREAM} Playback error: ${e.message}")
            }
        }
    }
}
