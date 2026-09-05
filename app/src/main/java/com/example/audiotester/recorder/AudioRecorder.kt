package com.example.audiotester.recorder

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.util.Log
import com.example.audiotester.common.AudioConstants
import com.example.audiotester.common.AudioEngineBase
import com.example.audiotester.common.AudioErrorType
import com.example.audiotester.common.AudioState
import com.example.audiotester.common.WavFile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Audio recorder based on the AudioRecord API.
 */
class AudioRecorder(private val context: Context) : AudioEngineBase() {

    companion object {
        private const val TAG = "AudioRecorder"
    }

    override val tag: String get() = TAG

    // Engine-lock-confined: every read/write happens under the engine lock; the run loop works
    // on locals captured in startLoop (see there)
    private var audioRecord: AudioRecord? = null
    private var wavFile: WavFile? = null

    override val alreadyActiveMessage = "Already recording"
    override val permissionDeniedMessage = "Recording permission denied"
    override val startupFailedMessage = "Recording initialization failed"
    override val startedMessage = "Recording started successfully"

    override fun releaseAudioResources() {
        // Independent steps: one failing release must not strand the resources after it.
        // WavFile.close() never throws (all IO errors are reported via its return value).
        try {
            audioRecord?.apply {
                if (this.state == AudioRecord.STATE_INITIALIZED) stop()
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioRecord", e)
        }
        audioRecord = null

        wavFile?.let {
            // A failed header patch leaves data behind a placeholder header — an unreadable
            // file. Surface it, unless an error was already reported for this session
            // (handleError sets ERROR): the finalize failure must not displace the root cause
            if (!it.close() && state != AudioState.ERROR) {
                // Logged as well as reported: during ViewModel teardown (onCleared) the
                // listener's updateUI lands on an already-cancelled viewModelScope, so this
                // is the only trace the failure leaves
                Log.e(TAG, "Failed to finalize the recording file (the header is incomplete)")
                engineListener?.onError(AudioErrorType.FINALIZE, "Failed to finalize the recording file (the header is incomplete; the file may be unreadable)")
            }
        }
        wavFile = null
    }

    /**
     * Output path: empty or asset:// → auto-generate a path in the app's private directory
     * (works on normal installs); otherwise use the configured path.
     */
    override fun openResources(): Boolean {
        // Reject unknown enum strings before touching the file system: the parse-time fallback
        // would silently record with the default source while the test looks successful
        AudioConstants.findUnknownRecorderEnum(currentConfig.audioSource)?.let {
            handleError(AudioErrorType.PARAM, "Unknown $it")
            return false
        }

        // Validate before touching the file system: an invalid config must not leave an
        // orphan WAV behind
        if (!validateAudioParameters()) return false

        val outputPath = if (currentConfig.hasUsableFilePath) currentConfig.audioFilePath
            else generateOutputFilePath()

        return try {
            val wavFile = WavFile(outputPath)
            this.wavFile = wavFile
            val channelCount = currentConfig.channelCount
            val bitsPerSample = currentConfig.audioFormat

            if (wavFile.create(currentConfig.sampleRate, channelCount, bitsPerSample)) {
                Log.d(TAG, "Output file created: $outputPath (${channelCount} channels)")
                true
            } else {
                val file = File(outputPath)
                val parentDir = file.parentFile
                val errorMsg = if (parentDir != null && !parentDir.canWrite()) {
                    "No write permission for directory: ${parentDir.absolutePath}"
                } else {
                    "Cannot create output file: $outputPath"
                }
                handleError(AudioErrorType.FILE, errorMsg)
                false
            }
        } catch (e: SecurityException) {
            handleError(AudioErrorType.PERMISSION, "Permission denied when creating file: $outputPath - ${e.message}")
            false
        } catch (e: Exception) {
            handleError(AudioErrorType.FILE, "Failed to create output file: $outputPath - ${e.message}")
            false
        }
    }

    override fun initializeAudio(session: Int): Boolean {
        // session unused: the recorder has no session-scoped callbacks to bind
        return try {
            val minBufferSize = AudioRecord.getMinBufferSize(
                currentConfig.sampleRate,
                AudioConstants.getInputChannelMask(currentConfig.channelCount),
                AudioConstants.getFormatFromBitDepth(currentConfig.audioFormat)
            )
            Log.i(TAG, "getMinBufferSize: $minBufferSize bytes")

            if (minBufferSize <= 0) {
                handleError(AudioErrorType.PARAM, "Unsupported audio parameter combination")
                return false
            }

            val bufferSize = minBufferSize * currentConfig.bufferMultiplier

            audioRecord = AudioRecord.Builder()
                .setAudioSource(AudioConstants.getAudioSource(currentConfig.audioSource))
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(currentConfig.sampleRate)
                        .setChannelMask(AudioConstants.getInputChannelMask(currentConfig.channelCount))
                        .setEncoding(AudioConstants.getFormatFromBitDepth(currentConfig.audioFormat))
                        .build()
                ).setBufferSizeInBytes(bufferSize).build()

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                handleError(AudioErrorType.STREAM, "AudioRecord initialization failed")
                return false
            }

            Log.i(TAG, "AudioRecord initialized successfully - ${currentConfig.description}")
            true
        } catch (e: SecurityException) {
            handleError(AudioErrorType.PERMISSION, "$permissionDeniedMessage: ${e.message}")
            false
        } catch (e: Exception) {
            handleError(AudioErrorType.STREAM, "AudioRecord creation failed: ${e.message}")
            false
        }
    }

    private fun validateAudioParameters(): Boolean {
        val sampleRate = currentConfig.sampleRate
        val channelCount = currentConfig.channelCount
        val bitsPerSample = currentConfig.audioFormat

        return when {
            !AudioConstants.isValidSampleRate(sampleRate) -> {
                handleError(AudioErrorType.PARAM, "Unsupported sample rate: ${sampleRate}Hz")
                false
            }
            !AudioConstants.isValidInputChannelCount(channelCount) -> {
                handleError(AudioErrorType.PARAM, "Unsupported input channel count: $channelCount (supported: 1/2/8/10/12/14/16)")
                false
            }
            !AudioConstants.isValidBitDepth(bitsPerSample) -> {
                handleError(AudioErrorType.PARAM, "Unsupported bit depth: ${bitsPerSample}bit")
                false
            }
            else -> true
        }
    }

    override fun startLoop(session: Int) {
        // Same-thread capture under the engine lock — the why lives in the startLoop contract
        // (AudioEngineBase); a stop landing before the body runs is absorbed by handleLoopError
        val audioRecord = audioRecord ?: error("Engine contract violation: null resources in startLoop")
        val wavFile = wavFile ?: error("Engine contract violation: null resources in startLoop")
        loopJob = loopScope.launch {
            // Everything after launch is inside the try: a stop landing mid-prologue releases the
            // audio objects concurrently, so even buffer setup must funnel through handleLoopError
            try {
                // bufferSizeInFrames / 3 keeps the read buffer a whole number of frames: a read
                // returning a partial frame would shift every block written to the WAV into
                // misaligned noise (the player rounds its write buffer the same way)
                val readBufferSize = audioRecord.bufferSizeInFrames / 3 * wavFile.blockAlign

                val buffer = ByteArray(readBufferSize)
                var totalBytes = 0L
                var lastLoggedBytes = 0L
                var saveFailed = false

                audioRecord.startRecording()
                Log.i(TAG, "Started recording - ${currentConfig.description}")

                while (isActive && state == AudioState.ACTIVE) {
                    // Recording has no natural EOF: while ACTIVE, read returning <= 0 can only
                    // mean a track error — abort as an error rather than pretending a normal
                    // completion (the state guard in catch keeps the stop race from false alarms)
                    val bytesRead = audioRecord.read(buffer, 0, buffer.size)
                    if (bytesRead <= 0) {
                        throw IOException("AudioRecord read failed: $bytesRead")
                    }

                    if (!saveFailed && !wavFile.writeAudioData(buffer, 0, bytesRead)) {
                        saveFailed = true  // WavFile closes itself on failure, retrying is pointless; recording continues, data is no longer saved
                        Log.e(TAG, "File save failed - recording continues without saving")
                    }
                    totalBytes += bytesRead
                    if (totalBytes - lastLoggedBytes >= PROGRESS_LOG_INTERVAL_BYTES) {
                        val mbRecorded = totalBytes / (1024.0 * 1024.0)
                        Log.v(TAG, "Progress: %.1fMB".format(Locale.US, mbRecorded))
                        lastLoggedBytes = totalBytes
                    }
                }

                if (state == AudioState.ACTIVE) {
                    val mbTotal = totalBytes / (1024.0 * 1024.0)
                    if (saveFailed) {
                        Log.w(TAG, "Recording finished: %.1fMB captured, file saving aborted".format(Locale.US, mbTotal))
                    } else {
                        Log.i(TAG, "Recording completed: %.1fMB".format(Locale.US, mbTotal))
                    }
                    stopIfSession(session)
                }
            } catch (e: OutOfMemoryError) {
                // A resource condition, not a bug: report it instead of crashing the process —
                // e.message carries the failed allocation size (ART), and releaseAudioResources
                // then frees the very buffers that caused it. Other Errors still crash on purpose
                handleLoopError(session, AudioErrorType.STREAM, "Buffer allocation failed: ${e.message}")
            } catch (e: SecurityException) {
                handleLoopError(session, AudioErrorType.PERMISSION, "Recording permission denied: ${e.message}")
            } catch (e: Exception) {
                handleLoopError(session, AudioErrorType.STREAM, "Recording error: ${e.message}")
            }
        }
    }

    private fun generateOutputFilePath(): String {
        val directory = context.getExternalFilesDir(null)?.absolutePath ?: context.filesDir.absolutePath
        val dateTime = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmssSSS"))
        val channelCount = currentConfig.channelCount
        val bitsPerSample = currentConfig.audioFormat
        val sampleRateK = currentConfig.sampleRate / 1000
        val fileName = "rec_${dateTime}_${sampleRateK}k_${channelCount}ch_${bitsPerSample}bit.wav"
        return File(directory, fileName).absolutePath
    }
}
