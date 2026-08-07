package com.example.audiotester.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
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
import java.io.IOException

/**
 * 音频播放器，基于 AudioTrack API，实现 [AudioEngine]。
 */
class AudioPlayer(private val context: Context) : AudioEngine {

    companion object {
        private const val TAG = "AudioPlayer"
    }

    private var audioTrack: AudioTrack? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var wavFile: WavFile? = null

    @Volatile
    private var state = AudioState.IDLE
    private var playbackJob: Job? = null
    private val playbackScope = CoroutineScope(Dispatchers.IO)
    private var currentConfig: AudioConfig = AudioConfig()

    private var listener: AudioEngine.Listener? = null

    override fun setListener(listener: AudioEngine.Listener?) {
        this.listener = listener
    }

    override fun setAudioConfig(config: AudioConfig) {
        if (state == AudioState.ACTIVE) {
            Log.w(TAG, "Cannot change configuration while playing")
            return
        }
        currentConfig = config
        Log.i(TAG, "Configuration updated: ${config.description}")
    }

    override fun start(): Boolean {
        Log.d(TAG, "Starting playback")

        if (state == AudioState.ACTIVE) {
            Log.w(TAG, "Already playing")
            listener?.onError("Already playing")
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
            listener?.onStarted()

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

    override fun stop() {
        Log.d(TAG, "Stopping playback")

        if (state != AudioState.ACTIVE) return

        state = AudioState.IDLE
        playbackJob?.cancel()
        releaseResources()
        listener?.onStopped()

        Log.i(TAG, "Playback stopped")
    }

    override fun release() {
        stop()
        listener = null
        try {
            playbackScope.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "Error canceling playback scope", e)
        }
        Log.d(TAG, "AudioPlayer resources released")
    }

    override fun isActive(): Boolean = state == AudioState.ACTIVE

    /**
     * 打开音频文件。空路径 → 内置音源；asset:// 前缀 → assets；其余 → 普通文件。
     */
    private fun openAudioFile(): Boolean {
        val path = currentConfig.audioFilePath.ifEmpty { AudioConstants.DEFAULT_AUDIO_FILE }
        wavFile = WavFile(path)
        val opened = if (path.startsWith("asset://")) {
            try {
                wavFile!!.open(context.assets.open(path.removePrefix("asset://")))
            } catch (e: IOException) {
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

            if (minBufferSize == AudioTrack.ERROR_BAD_VALUE) {
                abandonAudioFocus()
                handleError("${AudioConstants.ErrorTypes.PARAM} Unsupported audio parameter combination: ${wavFile.sampleRate}Hz, ${wavFile.channelCount}ch, ${wavFile.bitsPerSample}bit")
                return false
            }

            val bufferSize = minBufferSize * currentConfig.bufferMultiplier

            val audioAttributes =
                AudioAttributes.Builder().setUsage(AudioConstants.getUsage(currentConfig.usage))
                    .setContentType(AudioConstants.getContentType(currentConfig.contentType))
                    .build()

            audioTrack = AudioTrack.Builder().setAudioAttributes(audioAttributes).setAudioFormat(
                AudioFormat.Builder().setSampleRate(wavFile.sampleRate).setChannelMask(channelMask)
                    .setEncoding(audioFormat).build()
            ).setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioConstants.getTransferMode(currentConfig.transferMode))
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
        if (!AudioConstants.isValidChannelCount(wavFile.channelCount)) {
            handleError("${AudioConstants.ErrorTypes.PARAM} Unsupported channel count: ${wavFile.channelCount} (supported range: 1-16 channels)")
            return false
        }
        if (wavFile.channelCount == 12) {
            Log.i(TAG, "Detected 7.1.4 audio configuration (12 channels)")
        }
        if (!AudioConstants.isValidBitDepth(wavFile.bitsPerSample)) {
            handleError("${AudioConstants.ErrorTypes.PARAM} Unsupported bit depth: ${wavFile.bitsPerSample}bit (supported: 8/16/24/32bit)")
            return false
        }
        return true
    }

    private fun requestAudioFocus(): Boolean {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val focusType = determineFocusType()

        val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            handleFocusChange(focusChange)
        }

        val audioAttributes =
            AudioAttributes.Builder().setUsage(AudioConstants.getUsage(currentConfig.usage))
                .setContentType(AudioConstants.getContentType(currentConfig.contentType)).build()

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
            usage.contains("EMERGENCY") || usage.contains("SAFETY") ->
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            usage.contains("NAVIGATION") || usage.contains("ANNOUNCEMENT") ->
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

            val audioTrackBufferSize =
                audioTrack.bufferSizeInFrames * wavFile.channelCount * (wavFile.bitsPerSample / 8)
            val writeBufferSize =
                when (AudioConstants.getPerformanceMode(currentConfig.performanceMode)) {
                    AudioTrack.PERFORMANCE_MODE_LOW_LATENCY -> audioTrackBufferSize / 4
                    AudioTrack.PERFORMANCE_MODE_POWER_SAVING -> audioTrackBufferSize / 2
                    else -> audioTrackBufferSize / 3
                }

            val buffer = ByteArray(writeBufferSize)
            var totalBytes = 0L

            try {
                audioTrack.play()

                while (isActive && state == AudioState.ACTIVE) {
                    val bytesRead = wavFile.readData(buffer, 0, buffer.size)
                    if (bytesRead <= 0) {
                        Log.d(TAG, "File reading completed")
                        break
                    }

                    val bytesWritten = audioTrack.write(buffer, 0, bytesRead)
                    if (bytesWritten < 0) {
                        Log.e(TAG, "AudioTrack write failed: $bytesWritten")
                        break
                    }

                    totalBytes += bytesRead

                    if (totalBytes % (5 * 1024 * 1024L) == 0L && totalBytes > 0) {
                        val mbPlayed = totalBytes / (1024.0 * 1024.0)
                        Log.v(TAG, "Progress: %.1fMB".format(mbPlayed))
                    }
                }

                if (state == AudioState.ACTIVE) {
                    val mbTotal = totalBytes / (1024.0 * 1024.0)
                    Log.i(TAG, "Playback completed: %.1fMB".format(mbTotal))
                    stop()
                }
            } catch (e: Exception) {
                if (state == AudioState.ACTIVE) {
                    handleError("${AudioConstants.ErrorTypes.STREAM} Playback error: ${e.message}")
                }
            }
        }
    }

    private fun releaseResources() {
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

    private fun handleError(message: String) {
        state = AudioState.ERROR
        Log.e(TAG, "Error: $message")
        listener?.onError(message)
        releaseResources()
    }
}
