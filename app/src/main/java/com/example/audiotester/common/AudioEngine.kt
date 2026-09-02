package com.example.audiotester.common

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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
 * Common scaffolding for AudioEngine: the full start/stop/release state machine lives here,
 * under one engine lock. The player and recorder keep only their truly different parts
 * (resource opening, audio object initialization, run loop, message texts).
 */
abstract class AudioEngineBase : AudioEngine {

    protected abstract val tag: String

    @Volatile
    protected var state = AudioState.IDLE
    // Named to avoid setListener: otherwise the setter generated for this protected var would clash with the interface method's JVM signature
    protected var engineListener: AudioEngine.Listener? = null
    protected var currentConfig: AudioConfig = AudioConfig()

    // Feature-specific texts surfaced by the shared start() skeleton
    protected abstract val alreadyActiveMessage: String
    protected abstract val permissionDeniedMessage: String
    protected abstract val startupFailedMessage: String
    protected abstract val startedMessage: String

    // All state transitions (start/stop/handleError/release) hold the engine lock, so they
    // serialize against each other instead of interleaving
    private var released = false

    protected val loopScope = CoroutineScope(Dispatchers.IO)
    protected var loopJob: Job? = null

    override fun setListener(listener: AudioEngine.Listener?) {
        engineListener = listener
    }

    override fun isActive(): Boolean = state == AudioState.ACTIVE

    /**
     * The single copy of the start state machine: guards → open → initialize → commit →
     * notify. Subclasses provide resource-specific hooks and texts; always under the engine lock.
     */
    @Synchronized
    final override fun start(): Boolean {
        Log.d(tag, "Starting")
        if (released) {
            Log.w(tag, "Ignoring start: engine is released")
            return false
        }
        if (state == AudioState.ACTIVE) {
            Log.w(tag, alreadyActiveMessage)
            engineListener?.onError(alreadyActiveMessage)
            return false
        }
        if (state == AudioState.ERROR) state = AudioState.IDLE
        return try {
            if (!openResources()) return false
            if (!initializeAudio()) return false
            state = AudioState.ACTIVE
            startLoop()
            engineListener?.onStarted()
            Log.i(tag, startedMessage)
            true
        } catch (e: SecurityException) {
            handleError("${AudioConstants.ErrorTypes.PERMISSION} $permissionDeniedMessage: ${e.message}")
            false
        } catch (e: Exception) {
            handleError("${AudioConstants.ErrorTypes.STREAM} $startupFailedMessage: ${e.message}")
            false
        }
    }

    /** Subclass: open the session's file/resource; report failures via handleError, return false */
    protected abstract fun openResources(): Boolean

    /** Subclass: build the AudioTrack/AudioRecord; report failures via handleError, return false */
    protected abstract fun initializeAudio(): Boolean

    /** Subclass: launch the run loop on loopScope (assign loopJob) */
    protected abstract fun startLoop()

    // Synchronized: start() reads currentConfig piecemeal (openResources → initializeAudio →
    // startLoop); a config swap must not interleave with an in-flight start. Under the engine
    // lock it parks until start commits, then the ACTIVE guard rejects it.
    @Synchronized
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
        loopJob?.cancel()
        releaseAudioResources()
        engineListener?.onStopped()
        Log.i(tag, "Stopped")
    }

    @Synchronized
    override fun release() {
        released = true
        stop()
        engineListener = null
        loopScope.cancel()
        Log.d(tag, "Engine resources released")
    }

    /** Mark ERROR, notify, release. Caller must hold the engine lock (start failure paths, handleLoopError) */
    protected fun handleError(message: String) {
        state = AudioState.ERROR
        Log.e(tag, "Error: $message")
        engineListener?.onError(message)
        releaseAudioResources()
    }

    /** Run-loop failure: a stop() that completed first is a clean exit, not an error */
    @Synchronized
    protected fun handleLoopError(message: String) {
        if (state == AudioState.ACTIVE) handleError(message)
    }

    companion object {
        /** Run-loop progress logging cadence */
        protected const val PROGRESS_LOG_INTERVAL_BYTES = 5 * 1024 * 1024L
    }

    /** Subclass: release audio resources (stop/handleError paths; always under the engine lock) */
    protected abstract fun releaseAudioResources()
}
