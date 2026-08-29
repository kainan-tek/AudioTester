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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

/**
 * 音频播放器，基于 AudioTrack API。
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

    private var playbackJob: Job? = null
    private val playbackScope = CoroutineScope(Dispatchers.IO)

    override fun start(): Boolean {
        Log.d(TAG, "Starting playback")

        if (state == AudioState.ACTIVE) {
            Log.w(TAG, "Already playing")
            engineListener?.onError("Already playing")
            return false
        }
        if (state == AudioState.ERROR) {
            state = AudioState.IDLE
        }

        return try {
            if (!openAudioFile()) return false
            if (!initializeAudioTrack()) return false

            state = AudioState.ACTIVE
            startPlaybackLoop()
            if (state != AudioState.ACTIVE) {
                // 启动窗口内被 stop() 抢先（如焦点丢失回调）：资源已由 stop 释放、
                // UI 已由 onStopped 同步，再触发 onStarted 会把 UI 卡死在 ACTIVE
                return true
            }
            engineListener?.onStarted()

            Log.i(TAG, "Playback started successfully")
            true
        } catch (e: SecurityException) {
            handleError("${AudioConstants.ErrorTypes.PERMISSION} Permission denied: ${e.message}")
            false
        } catch (e: Exception) {
            handleError("${AudioConstants.ErrorTypes.STREAM} Playback initialization failed: ${e.message}")
            false
        }
    }

    override fun cancelJob() {
        playbackJob?.cancel()
    }

    override fun cancelScope() {
        playbackScope.cancel()
    }

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
     * 打开音频文件。空路径 → 内置音源；asset:// 前缀 → assets；其余 → 普通文件。
     */
    private fun openAudioFile(): Boolean {
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

    private fun initializeAudioTrack(): Boolean {
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

        // 系统 usage（>=1000）属车辆关键音频、不依赖普通焦点管理，跳过焦点请求。
        // （SDK usage 恒 <1000，本判断只对 Usage.SYSTEM_MAP 的配置生效）
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
     * UI 不支持暂停，所有焦点丢失都转为停止
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

    private fun startPlaybackLoop() {
        playbackJob = playbackScope.launch {
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
            // 向下取整到帧倍数：write() 只消费整帧（userSize >= mFrameSize 循环），残帧尾巴
            // 永远写不进去且返回 0；非帧倍数块会每块丢字节、后续数据整体错位成噪声
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

                    // 块已帧对齐：阻塞式 write() 内部排空循环要么消费全部整帧、要么出错
                    // （部分成功后必接错误码）。短写即异常，响亮失败以暴露平台问题
                    val bytesWritten = audioTrack.write(buffer, 0, bytesRead)
                    if (bytesWritten != bytesRead) {
                        throw IOException("AudioTrack write incomplete: $bytesWritten/$bytesRead")
                    }
                    totalBytes += bytesWritten
                    if (totalBytes - lastLoggedBytes >= 5 * 1024 * 1024L) {
                        val mbPlayed = totalBytes / (1024.0 * 1024.0)
                        Log.v(TAG, "Progress: %.1fMB".format(Locale.US, mbPlayed))
                        lastLoggedBytes = totalBytes
                    }
                }

                if (state == AudioState.ACTIVE) {
                    // 正常播完（EOF）时 stop() 会丢弃 track 缓冲里未播完的帧，导致尾部被截。
                    // 轮询 playbackHeadPosition 排空后再 stop，确保整段音频都被听到。
                    if (readLoopEnded) {
                        val framesWritten = totalBytes / bytesPerFrame
                        // 排空最长 = track 缓冲时长（大 minBufferSize 设备可达秒级），
                        // deadline 按缓冲时长缩放 + 2s 余量，防 HAL 异常卡死；10ms 轮询足够
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
                    stop()
                }
            } catch (e: Exception) {
                if (state == AudioState.ACTIVE) {
                    handleError("${AudioConstants.ErrorTypes.STREAM} Playback error: ${e.message}")
                }
            }
        }
    }
}
