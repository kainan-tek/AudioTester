package com.example.audiotester.common

import android.util.Log
import kotlin.jvm.Synchronized

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

/** 统一音频状态枚举（原 PlayerState / RecorderState 结构相同，合并） */
enum class AudioState { IDLE, ACTIVE, ERROR }

/**
 * AudioEngine 公共脚手架：状态/监听器/启停/释放/错误处理单点化。
 * 播放器与录音器只保留真正差异的部分（音频对象初始化、运行循环、start 骨架）。
 */
abstract class AudioEngineBase : AudioEngine {

    protected abstract val tag: String

    @Volatile
    protected var state = AudioState.IDLE
    // 命名避开 setListener：否则 protected var 生成的 setListener 访问器会与接口方法 JVM 签名冲突
    protected var engineListener: AudioEngine.Listener? = null
    protected var currentConfig: AudioConfig = AudioConfig()

    override fun setListener(listener: AudioEngine.Listener?) {
        engineListener = listener
    }

    override fun isActive(): Boolean = state == AudioState.ACTIVE

    override fun setAudioConfig(config: AudioConfig) {
        if (state == AudioState.ACTIVE) {
            Log.w(tag, "Cannot change configuration while active")
            return
        }
        currentConfig = config
        Log.i(tag, "Configuration updated: ${config.description}")
    }

    @Synchronized
    override fun stop() {
        Log.d(tag, "Stopping")
        if (state != AudioState.ACTIVE) return
        state = AudioState.IDLE
        cancelJob()
        releaseResources()
        engineListener?.onStopped()
        Log.i(tag, "Stopped")
    }

    override fun release() {
        stop()
        engineListener = null
        try {
            cancelScope()
        } catch (e: Exception) {
            Log.w(tag, "Error canceling scope", e)
        }
        Log.d(tag, "Engine resources released")
    }

    @Synchronized
    protected fun releaseResources() {
        releaseAudioResources()
    }

    protected fun handleError(message: String) {
        state = AudioState.ERROR
        Log.e(tag, "Error: $message")
        engineListener?.onError(message)
        releaseResources()
    }

    /** 子类：取消运行循环 job（stop 用） */
    protected abstract fun cancelJob()

    /** 子类：取消协程作用域（release 用） */
    protected abstract fun cancelScope()

    /** 子类：释放音频资源（stop/handleError 用，已由基类加锁） */
    protected abstract fun releaseAudioResources()
}
