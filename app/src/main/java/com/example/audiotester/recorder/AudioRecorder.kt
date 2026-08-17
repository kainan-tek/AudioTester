package com.example.audiotester.recorder

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.util.Log
import com.example.audiotester.common.AudioConfig
import com.example.audiotester.common.AudioConstants
import com.example.audiotester.common.AudioEngine
import com.example.audiotester.common.AudioState
import com.example.audiotester.common.WavFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * 音频录制器，基于 AudioRecord API，实现 [AudioEngine]。
 */
class AudioRecorder(private val context: Context) : AudioEngine {

    companion object {
        private const val TAG = "AudioRecorder"
    }

    private var audioRecord: AudioRecord? = null
    private var wavFile: WavFile? = null

    @Volatile
    private var state = AudioState.IDLE
    private var recordingJob: Job? = null
    private val recordingScope = CoroutineScope(Dispatchers.IO)
    private var currentConfig: AudioConfig = AudioConfig()

    private var listener: AudioEngine.Listener? = null

    override fun setListener(listener: AudioEngine.Listener?) {
        this.listener = listener
    }

    override fun setAudioConfig(config: AudioConfig) {
        if (state == AudioState.ACTIVE) {
            Log.w(TAG, "Cannot change configuration while recording")
            return
        }
        currentConfig = config
        Log.i(TAG, "Configuration updated: ${config.description}")
    }

    override fun start(): Boolean {
        Log.d(TAG, "Starting recording")

        if (state == AudioState.ACTIVE) {
            Log.w(TAG, "Already recording")
            listener?.onError("Already recording")
            return false
        }
        if (state == AudioState.ERROR) {
            state = AudioState.IDLE
        }

        return try {
            if (!createOutputFile()) return false
            if (!initializeAudioRecord()) return false

            state = AudioState.ACTIVE
            startRecordingLoop()
            listener?.onStarted()

            Log.i(TAG, "Recording started successfully")
            true
        } catch (e: SecurityException) {
            handleError("${AudioConstants.ErrorTypes.PERMISSION} Recording permission denied: ${e.message}")
            false
        } catch (e: Exception) {
            handleError("${AudioConstants.ErrorTypes.STREAM} Recording initialization failed: ${e.message}")
            false
        }
    }

    override fun stop() {
        Log.d(TAG, "Stopping recording")

        if (state != AudioState.ACTIVE) return

        state = AudioState.IDLE
        recordingJob?.cancel()
        releaseResources()
        listener?.onStopped()

        Log.i(TAG, "Recording stopped")
    }

    override fun release() {
        stop()
        listener = null
        try {
            recordingScope.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "Error canceling recording scope", e)
        }
        Log.d(TAG, "AudioRecorder resources released")
    }

    override fun isActive(): Boolean = state == AudioState.ACTIVE

    /**
     * 输出路径：空或 asset:// → 自动生成 App 私有目录路径（普通安装可用）；否则用配置路径。
     */
    private fun createOutputFile(): Boolean {
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

    private fun initializeAudioRecord(): Boolean {
        return try {
            if (!validateAudioParameters()) return false

            val minBufferSize = AudioRecord.getMinBufferSize(
                currentConfig.sampleRate,
                AudioConstants.getInputChannelMask(currentConfig.channelCount),
                AudioConstants.getFormatFromBitDepth(currentConfig.audioFormat)
            )
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

    private fun startRecordingLoop() {
        recordingJob = recordingScope.launch {
            val audioRecord = audioRecord ?: return@launch

            val audioRecordBufferSize =
                audioRecord.bufferSizeInFrames * currentConfig.channelCount * (currentConfig.audioFormat / 8)
            val readBufferSize = audioRecordBufferSize / 3

            val buffer = ByteArray(readBufferSize)
            var totalBytes = 0L
            var lastLoggedBytes = 0L

            try {
                audioRecord.startRecording()
                Log.i(TAG, "Started recording - ${currentConfig.description}")

                while (isActive && state == AudioState.ACTIVE) {
                    val bytesRead = audioRecord.read(buffer, 0, buffer.size)
                    if (bytesRead <= 0) {
                        Log.w(TAG, "AudioRecord read failed or reached end: $bytesRead")
                        break
                    }

                    val writeSuccess = wavFile?.writeAudioData(buffer, 0, bytesRead) ?: false
                    if (!writeSuccess) {
                        Log.e(TAG, "Failed to write audio data to file")
                    }
                    totalBytes += bytesRead

                    if (totalBytes - lastLoggedBytes >= 5 * 1024 * 1024L) {
                        val mbRecorded = totalBytes / (1024.0 * 1024.0)
                        Log.v(TAG, "Progress: %.1fMB".format(mbRecorded))
                        lastLoggedBytes = totalBytes
                    }
                }

                if (state == AudioState.ACTIVE) {
                    val mbRecorded = totalBytes / (1024.0 * 1024.0)
                    Log.i(TAG, "Recording completed: %.1fMB".format(mbRecorded))
                    stop()
                }
            } catch (e: SecurityException) {
                if (state == AudioState.ACTIVE) {
                    handleError("${AudioConstants.ErrorTypes.PERMISSION} Recording permission denied: ${e.message}")
                }
            } catch (e: Exception) {
                if (state == AudioState.ACTIVE) {
                    handleError("${AudioConstants.ErrorTypes.STREAM} Recording error: ${e.message}")
                }
            }
        }
    }

    private fun releaseResources() {
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

    private fun handleError(message: String) {
        state = AudioState.ERROR
        Log.e(TAG, "Error: $message")
        listener?.onError(message)
        releaseResources()
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
