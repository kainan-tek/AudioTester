package com.example.audiotester.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import com.example.audiotester.common.AudioConstants
import com.example.audiotester.common.AudioEngineBase
import com.example.audiotester.common.AudioErrorType
import com.example.audiotester.common.AudioState
import com.example.audiotester.common.WavFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

/**
 * Audio player based on the AudioTrack API.
 */
class AudioPlayer(private val context: Context) : AudioEngineBase() {

    companion object {
        private const val TAG = "AudioPlayer"
    }

    override val tag: String get() = TAG

    // Engine-lock-confined: every read/write happens under the engine lock; the run loop works
    // on locals captured in startLoop (see there). audioManager/audioFocusRequest the loop
    // never touches at all
    private var audioTrack: AudioTrack? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var wavFile: WavFile? = null

    override val alreadyActiveMessage = "Already playing"
    override val permissionDeniedMessage = "Permission denied"
    override val startupFailedMessage = "Playback initialization failed"
    override val startedMessage = "Playback started successfully"

    override fun releaseAudioResources() {
        // Independent steps: one failing release must not strand the resources after it.
        // WavFile.close() never throws (all IO errors are reported via its return value).
        try {
            audioTrack?.apply {
                if (this.state == AudioTrack.STATE_INITIALIZED) stop()
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioTrack", e)
        }
        audioTrack = null

        try {
            abandonAudioFocus()
        } catch (e: Exception) {
            Log.e(TAG, "Error abandoning audio focus", e)
        }
        audioManager = null

        wavFile?.close()
        wavFile = null
    }

    /**
     * Opens the audio file. Empty path → built-in source; asset:// prefix → assets; otherwise → regular file.
     */
    override fun openResources(): Boolean {
        // Reject unknown enum strings before opening anything: the parse-time fallback would
        // silently play with the default constants while the test looks successful
        AudioConstants.findUnknownPlayerEnum(currentConfig.usage, currentConfig.contentType, currentConfig.performanceMode)?.let {
            handleError(AudioErrorType.PARAM, "Unknown $it")
            return false
        }

        val path = currentConfig.audioFilePath.ifEmpty { AudioConstants.DEFAULT_AUDIO_FILE }
        val wavFile = WavFile(path)
        this.wavFile = wavFile
        val opened = if (path.startsWith("asset://")) {
            try {
                wavFile.open(context.assets.open(path.removePrefix("asset://")))
            } catch (_: IOException) {
                handleError(AudioErrorType.FILE, "Cannot open audio asset: $path")
                return false
            }
        } else {
            wavFile.open()
        }

        if (!opened) {
            handleError(AudioErrorType.FILE, "Cannot open audio file: $path")
            return false
        }

        Log.d(
            TAG,
            "Audio file opened: ${wavFile.sampleRate}Hz, ${wavFile.bitsPerSample}bit, ${wavFile.channelCount}ch"
        )
        return true
    }

    override fun initializeAudio(session: Int): Boolean {
        val wavFile = wavFile ?: return false

        // Every failure path funnels through handleError → releaseAudioResources, which already
        // abandons focus and releases the track; no per-path cleanup here
        try {
            // Built once and shared by the focus request below and the track creation
            val audioAttributes =
                AudioConstants.buildAudioAttributes(currentConfig.usage, currentConfig.contentType)

            if (!requestAudioFocus(audioAttributes, session)) {
                handleError(AudioErrorType.FOCUS, "Cannot obtain audio focus")
                return false
            }

            if (!validateAudioParameters(wavFile)) return false

            val channelMask = AudioConstants.getOutputChannelMask(wavFile.channelCount)
            val audioFormat = AudioConstants.getFormatFromBitDepth(wavFile.bitsPerSample)
            val minBufferSize = AudioTrack.getMinBufferSize(wavFile.sampleRate, channelMask, audioFormat)
            Log.i(TAG, "getMinBufferSize: $minBufferSize bytes")

            if (minBufferSize <= 0) {
                handleError(AudioErrorType.PARAM, "Unsupported audio parameter combination: ${wavFile.sampleRate}Hz, ${wavFile.channelCount}ch, ${wavFile.bitsPerSample}bit")
                return false
            }

            val bufferSize = minBufferSize * currentConfig.bufferMultiplier

            audioTrack = AudioTrack.Builder().setAudioAttributes(audioAttributes).setAudioFormat(
                AudioFormat.Builder().setSampleRate(wavFile.sampleRate).setChannelMask(channelMask)
                    .setEncoding(audioFormat).build()
            ).setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioConstants.getPerformanceMode(currentConfig.performanceMode))
                .build()

            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                handleError(AudioErrorType.STREAM, "AudioTrack initialization failed, state: ${audioTrack?.state}")
                return false
            }

            Log.i(
                TAG,
                "AudioTrack initialized - ${wavFile.sampleRate}Hz, ${wavFile.channelDescription}, " +
                    "${wavFile.bitsPerSample}bit, layout: ${wavFile.channelLayout}"
            )

            return true
        } catch (e: Exception) {
            handleError(AudioErrorType.STREAM, "AudioTrack creation failed: ${e.message}")
            return false
        }
    }

    private fun validateAudioParameters(wavFile: WavFile): Boolean {
        if (!AudioConstants.isValidSampleRate(wavFile.sampleRate)) {
            handleError(AudioErrorType.PARAM, "Unsupported sample rate: ${wavFile.sampleRate}Hz (supported range: 8000-192000Hz)")
            return false
        }
        if (!AudioConstants.isValidOutputChannelCount(wavFile.channelCount)) {
            handleError(AudioErrorType.PARAM, "Unsupported channel count: ${wavFile.channelCount} (supported: 1/2/4/6/8/10/12/16)")
            return false
        }
        return true
    }

    private fun requestAudioFocus(audioAttributes: AudioAttributes, session: Int): Boolean {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // System usages (>= 1000) are vehicle-critical audio and do not rely on regular focus
        // management, so skip the focus request.
        // (SDK usages are always < 1000; this check only affects Usage.SYSTEM_MAP configs)
        if (AudioConstants.isSystemUsage(currentConfig.usage)) return true

        // Bind the focus callbacks to this session: initializeAudio runs under the engine lock
        // during start(), so a queued focus-loss stop can never land on a newer session
        val focusType = determineFocusType()

        val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            handleFocusChange(focusChange, session)
        }

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
    private fun handleFocusChange(focusChange: Int, session: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.d(TAG, "Audio focus lost (type: $focusChange), stopping playback")
                // Focus callbacks land on the main thread; stopIfSession takes the engine lock
                // and closes the WAV (disk IO) — post it off-main to keep the callback cheap
                loopScope.launch { stopIfSession(session) }
            }
        }
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let { request ->
            audioManager?.abandonAudioFocusRequest(request)
            audioFocusRequest = null
        }
    }

    override fun startLoop(session: Int) {
        // Same-thread capture under the engine lock — the why lives in the startLoop contract
        // (AudioEngineBase); a stop landing before the body runs is absorbed by handleLoopError
        val wavFile = wavFile ?: error("Engine contract violation: null resources in startLoop")
        val audioTrack = audioTrack ?: error("Engine contract violation: null resources in startLoop")
        loopJob = loopScope.launch {
            // Everything after launch is inside the try: a stop landing mid-prologue releases the
            // audio objects concurrently, so even buffer setup must funnel through handleLoopError
            try {
                val bytesPerFrame = wavFile.blockAlign
                val audioTrackBufferSize = audioTrack.bufferSizeInFrames * bytesPerFrame
                val rawWriteBufferSize =
                    when (AudioConstants.getPerformanceMode(currentConfig.performanceMode)) {
                        AudioTrack.PERFORMANCE_MODE_LOW_LATENCY -> audioTrackBufferSize / 4
                        AudioTrack.PERFORMANCE_MODE_POWER_SAVING -> audioTrackBufferSize / 2
                        else -> audioTrackBufferSize / 3
                    }
                // Round down to whole frames: write() only consumes whole frames; a leftover
                // partial frame can never be written (returns 0) and non-frame-aligned blocks
                // would drop bytes every block, shifting all subsequent data into noise
                val writeBufferSize = rawWriteBufferSize / bytesPerFrame * bytesPerFrame

                val buffer = ByteArray(writeBufferSize)
                var totalBytes = 0L
                var lastLoggedBytes = 0L

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

                    // The blocking write()'s internal drain loop either consumes all whole frames
                    // or errors out (partial success is always followed by an error code) — treat
                    // a short write as an exception and fail loudly to expose platform issues
                    val bytesWritten = audioTrack.write(buffer, 0, alignedBytes)
                    if (bytesWritten != alignedBytes) {
                        throw IOException("AudioTrack write incomplete: $bytesWritten/$alignedBytes")
                    }
                    // Accumulate before the partial-tail break: these frames are queued in the
                    // track, and the drain below must wait for all of them
                    totalBytes += bytesWritten
                    if (alignedBytes != bytesRead) {
                        readLoopEnded = true   // partial tail = end of data; drain and stop below
                        break
                    }
                    if (totalBytes - lastLoggedBytes >= PROGRESS_LOG_INTERVAL_BYTES) {
                        val mbPlayed = totalBytes / (1024.0 * 1024.0)
                        Log.v(TAG, "Progress: %.1fMB".format(Locale.US, mbPlayed))
                        lastLoggedBytes = totalBytes
                    }
                }

                if (state == AudioState.ACTIVE) {
                    // A mid-file IO error throws out of readData (→ catch below → [STREAM]); a
                    // file that ends before its declared data size (truncated copy / lying
                    // header) is a file-content problem — fail loudly as [TRUNCATED], not as
                    // a partial-playback success
                    if (readLoopEnded && wavFile.hasUnreadDeclaredData) {
                        handleLoopError(
                            session,
                            AudioErrorType.TRUNCATED,
                            "audio data ended ${wavFile.remainingData} bytes short of the declared size")
                        return@launch
                    }
                    // On a natural end (EOF), stop() would discard frames still buffered in the
                    // track, cutting off the tail — poll playbackHeadPosition until drained
                    // (10ms steps), then stop, so the entire audio is heard
                    if (readLoopEnded) {
                        val framesWritten = totalBytes / bytesPerFrame
                        // Deadline = track buffer duration (seconds on devices with a large
                        // minBufferSize) + 2s margin against a stuck HAL
                        val drainMs = audioTrack.bufferSizeInFrames * 1000L / wavFile.sampleRate
                        val deadline = SystemClock.elapsedRealtime() + drainMs + 2000
                        while (isActive && state == AudioState.ACTIVE &&
                            audioTrack.playbackHeadPosition < framesWritten &&
                            SystemClock.elapsedRealtime() < deadline) {
                            delay(10.milliseconds)
                        }
                    }
                    val mbTotal = totalBytes / (1024.0 * 1024.0)
                    Log.i(TAG, "Playback completed: %.1fMB".format(Locale.US, mbTotal))
                    stopIfSession(session)
                }
            } catch (e: CancellationException) {
                // Cancellation is a stop, not an error: rethrow rather than rely on
                // handleLoopError's guard (it only no-ops because stopLocked sets IDLE before cancel)
                throw e
            } catch (e: OutOfMemoryError) {
                // A resource condition, not a bug: report it instead of crashing the process —
                // e.message carries the failed allocation size (ART), and releaseAudioResources
                // then frees the very buffers that caused it. Other Errors still crash on purpose
                handleLoopError(session, AudioErrorType.STREAM, "Buffer allocation failed: ${e.message}")
            } catch (e: Exception) {
                handleLoopError(session, AudioErrorType.STREAM, "Playback error: ${e.message}")
            }
        }
    }
}
