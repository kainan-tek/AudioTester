package com.example.audiotester.common

import android.util.Log
import kotlin.jvm.Synchronized

/**
 * Audio engine interface: implemented by both AudioPlayer (AudioTrack + focus) and AudioRecorder (AudioRecord).
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

/** Unified audio state enum (former PlayerState / RecorderState had identical structure, merged) */
enum class AudioState { IDLE, ACTIVE, ERROR }

/**
 * Common scaffolding for AudioEngine: state/listener/start-stop/release/error handling centralized in one place.
 * The player and recorder keep only their truly different parts (audio object initialization, run loop, start skeleton).
 */
abstract class AudioEngineBase : AudioEngine {

    protected abstract val tag: String

    @Volatile
    protected var state = AudioState.IDLE
    // Named to avoid setListener: otherwise the setter generated for this protected var would clash with the interface method's JVM signature
    protected var engineListener: AudioEngine.Listener? = null
    protected var currentConfig: AudioConfig = AudioConfig()

    // All state transitions (start/stop/handleError/release) hold the engine lock, so they
    // serialize against each other instead of interleaving
    private var released = false

    override fun setListener(listener: AudioEngine.Listener?) {
        engineListener = listener
    }

    override fun isActive(): Boolean = state == AudioState.ACTIVE

    /**
     * Template start: holds the engine lock, so a stop()/release() landing mid-start waits
     * behind it instead of interleaving with it
     */
    @Synchronized
    final override fun start(): Boolean {
        if (released) {
            Log.w(tag, "Ignoring start: engine is released")
            return false
        }
        return doStart()
    }

    /** Subclass start implementation; always invoked under the engine lock */
    protected abstract fun doStart(): Boolean

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

    @Synchronized
    override fun release() {
        released = true
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

    /** Mark ERROR, notify, release. Caller must hold the engine lock (doStart failure paths, handleLoopError) */
    protected fun handleError(message: String) {
        state = AudioState.ERROR
        Log.e(tag, "Error: $message")
        engineListener?.onError(message)
        releaseResources()
    }

    /** Run-loop failure: a stop() that completed first is a clean exit, not an error */
    @Synchronized
    protected fun handleLoopError(message: String) {
        if (state == AudioState.ACTIVE) handleError(message)
    }

    /** Subclass: cancel the run-loop job (used by stop) */
    protected abstract fun cancelJob()

    /** Subclass: cancel the coroutine scope (used by release) */
    protected abstract fun cancelScope()

    /** Subclass: release audio resources (used by stop/handleError; already locked by the base class) */
    protected abstract fun releaseAudioResources()
}
