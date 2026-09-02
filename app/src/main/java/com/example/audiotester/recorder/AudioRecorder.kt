package com.example.audiotester.recorder

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.util.Log
import com.example.audiotester.common.AudioConstants
import com.example.audiotester.common.AudioEngineBase
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

    private var audioRecord: AudioRecord? = null
    private var wavFile: WavFile? = null

    override val alreadyActiveMessage = "Already recording"
    override val permissionDeniedMessage = "Recording permission denied"
    override val startupFailedMessage = "Recording initialization failed"
    override val startedMessage = "Recording started successfully"

    override fun releaseAudioResources() {
        try {
            audioRecord?.apply {
                if (this.state == AudioRecord.STATE_INITIALIZED) stop()
                release()
            }
            audioRecord = null

            wavFile?.close()
            wavFile = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing resources", e)
        }
    }

    /**
     * Output path: empty or asset:// → auto-generate a path in the app's private directory
     * (works on normal installs); otherwise use the configured path.
     */
    override fun openResources(): Boolean {
        // Validate before touching the file system: an invalid config must not leave an
        // orphan WAV behind
        if (!validateAudioParameters()) return false

        val outputPath = currentConfig.audioFilePath
            .takeIf { it.isNotEmpty() && !it.startsWith("asset://") }
            ?: generateOutputFilePath()

        return try {
            wavFile = WavFile(outputPath)
            val channelCount = currentConfig.channelCount
            val bitsPerSample = currentConfig.audioFormat

            if (wavFile!!.create(currentConfig.sampleRate, channelCount, bitsPerSample)) {
                Log.d(TAG, "Output file created: $outputPath (${channelCount} channels)")
                true
            } else {
                val file = File(outputPath)
                val parentDir = file.parentFile
                val errorMsg = if (parentDir != null && !parentDir.canWrite()) {
                    "${AudioConstants.ErrorTypes.FILE} No write permission for directory: ${parentDir.absolutePath}"
                } else {
                    "${AudioConstants.ErrorTypes.FILE} Cannot create output file: $outputPath"
                }
                handleError(errorMsg)
                false
            }
        } catch (e: SecurityException) {
            handleError("${AudioConstants.ErrorTypes.PERMISSION} Permission denied when creating file: $outputPath - ${e.message}")
            false
        } catch (e: Exception) {
            handleError("${AudioConstants.ErrorTypes.FILE} Failed to create output file: $outputPath - ${e.message}")
            false
        }
    }

    override fun initializeAudio(): Boolean {
        return try {
            val minBufferSize = AudioRecord.getMinBufferSize(
                currentConfig.sampleRate,
                AudioConstants.getInputChannelMask(currentConfig.channelCount),
                AudioConstants.getFormatFromBitDepth(currentConfig.audioFormat)
            )
            Log.i(TAG, "getMinBufferSize: $minBufferSize bytes")

            if (minBufferSize <= 0) {
                handleError("${AudioConstants.ErrorTypes.PARAM} Unsupported audio parameter combination")
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
                handleError("${AudioConstants.ErrorTypes.STREAM} AudioRecord initialization failed")
                return false
            }

            Log.i(TAG, "AudioRecord initialized successfully - ${currentConfig.description}")
            true
        } catch (_: SecurityException) {
            handleError("${AudioConstants.ErrorTypes.PERMISSION} Recording permission denied")
            false
        } catch (e: Exception) {
            handleError("${AudioConstants.ErrorTypes.STREAM} AudioRecord creation failed: ${e.message}")
            false
        }
    }

    private fun validateAudioParameters(): Boolean {
        val sampleRate = currentConfig.sampleRate
        val channelCount = currentConfig.channelCount
        val bitsPerSample = currentConfig.audioFormat

        return when {
            !AudioConstants.isValidSampleRate(sampleRate) -> {
                handleError("${AudioConstants.ErrorTypes.PARAM} Unsupported sample rate: ${sampleRate}Hz")
                false
            }
            !AudioConstants.isValidInputChannelCount(channelCount) -> {
                handleError("${AudioConstants.ErrorTypes.PARAM} Unsupported input channel count: $channelCount (supported: 1/2/8/10/12/14/16)")
                false
            }
            !AudioConstants.isValidBitDepth(bitsPerSample) -> {
                handleError("${AudioConstants.ErrorTypes.PARAM} Unsupported bit depth: ${bitsPerSample}bit")
                false
            }
            else -> true
        }
    }

    override fun startLoop() {
        loopJob = loopScope.launch {
            val audioRecord = audioRecord ?: return@launch
            val wavFile = wavFile ?: return@launch

            val readBufferSize = audioRecord.bufferSizeInFrames * wavFile.blockAlign / 3

            val buffer = ByteArray(readBufferSize)
            var totalBytes = 0L
            var lastLoggedBytes = 0L
            var saveFailed = false

            try {
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
                    stop()
                }
            } catch (e: SecurityException) {
                handleLoopError("${AudioConstants.ErrorTypes.PERMISSION} Recording permission denied: ${e.message}")
            } catch (e: Exception) {
                handleLoopError("${AudioConstants.ErrorTypes.STREAM} Recording error: ${e.message}")
            }
        }
    }

    private fun generateOutputFilePath(): String {
        val directory = context.getExternalFilesDir(null)?.absolutePath ?: context.filesDir.absolutePath
        val dateTime = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val channelCount = currentConfig.channelCount
        val bitsPerSample = currentConfig.audioFormat
        val sampleRateK = currentConfig.sampleRate / 1000
        val fileName = "rec_${dateTime}_${sampleRateK}k_${channelCount}ch_${bitsPerSample}bit.wav"
        return File(directory, fileName).absolutePath
    }
}
