package com.example.audiotester.common

/**
 * 音频引擎接口：AudioPlayer（AudioTrack+焦点）与 AudioRecorder（AudioRecord）共同实现。
 */
interface AudioEngine {

    interface Listener {
        fun onStarted()
        fun onStopped()
        fun onError(error: String)
    }

    fun setAudioConfig(config: AudioConfig)
    fun start(): Boolean
    fun stop()
    fun release()
    fun isActive(): Boolean
    fun setListener(listener: Listener?)
}
