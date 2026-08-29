package com.example.audiotester.recorder

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.util.Log
import com.example.audiotester.common.AudioConstants
import com.example.audiotester.common.AudioEngineBase
import com.example.audiotester.common.AudioState
import com.example.audiotester.common.WavFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * 音频录制器，基于 AudioRecord API。
 */
class AudioRecorder(private val context: Context) : AudioEngineBase() {

    companion object {
        private const val TAG = "AudioRecorder"
    }

    override val tag: String get() = TAG

    private var audioRecord: AudioRecord? = null
    private var wavFile: WavFile? = null

    private var recordingJob: Job? = null
    private val recordingScope = CoroutineScope(Dispatchers.IO)

    override fun start(): Boolean {
        Log.d(TAG, "Starting recording")

        if (state == AudioState.ACTIVE) {
            Log.w(TAG, "Already recording")
            engineListener?.onError("Already recording")
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
            engineListener?.onStarted()

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

    override fun cancelJob() {
        recordingJob?.cancel()
    }

    override fun cancelScope() {
        recordingScope.cancel()
    }

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

    private fun startRecordingLoop() {
        recordingJob = recordingScope.launch {
            val audioRecord = audioRecord ?: return@launch

            val audioRecordBufferSize =
                audioRecord.bufferSizeInFrames * currentConfig.channelCount * (currentConfig.audioFormat / 8)
            val readBufferSize = audioRecordBufferSize / 3

            val buffer = ByteArray(readBufferSize)
            var totalBytes = 0L
            var lastLoggedBytes = 0L
            var saveFailed = false

            try {
                audioRecord.startRecording()
                Log.i(TAG, "Started recording - ${currentConfig.description}")

                while (isActive && state == AudioState.ACTIVE) {
                    // 录音无自然 EOF：ACTIVE 下 read 返回 ≤0 只能是 track 异常，
                    // 按错误中止而非伪装成正常完成（catch 的 state 守卫保证停止竞态不误报）
                    val bytesRead = audioRecord.read(buffer, 0, buffer.size)
                    if (bytesRead <= 0) {
                        throw IOException("AudioRecord read failed: $bytesRead")
                    }

                    if (!saveFailed && wavFile?.writeAudioData(buffer, 0, bytesRead) != true) {
                        saveFailed = true  // WavFile 失败即自关闭，重试无意义；录音继续，数据不再落盘
                        Log.e(TAG, "File save failed - recording continues without saving")
                    }
                    totalBytes += bytesRead
                    if (totalBytes - lastLoggedBytes >= 5 * 1024 * 1024L) {
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
