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
        /** [detail] is diagnostic (already logged engine-side); the UI consumes only the type */
        fun onError(type: AudioErrorType, detail: String)
    }

    /** Applies the config and returns what the engine will actually use: the argument itself
     *  if accepted, the previously applied config if rejected while ACTIVE */
    fun setAudioConfig(config: AudioConfig): AudioConfig
    fun start(): Boolean
    fun stop()
    fun release()
    fun setListener(listener: Listener?)
}

/**
 * Unified audio state enum (former PlayerState / RecorderState had identical structure, merged).
 * STARTING is ViewModel-side only: start requested but not yet committed (the engine jumps
 * IDLE → ACTIVE synchronously under its lock and never reports it).
 */
enum class AudioState { IDLE, STARTING, ACTIVE, ERROR }

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

    /**
     * Session token, incremented under the engine lock at each start commit. Loop-side callers
     * (run loop, focus-loss callback) capture their own token and pass it back on stop/error;
     * the compare+act under the engine lock makes a stale caller a no-op instead of landing
     * on a newer session.
     */
    private var session = 0

    /** Current session token; read under the engine lock to bind callbacks to this session (e.g. focus setup during start) */
    protected val currentSession: Int get() = session

    protected val loopScope = CoroutineScope(Dispatchers.IO)
    protected var loopJob: Job? = null

    override fun setListener(listener: AudioEngine.Listener?) {
        engineListener = listener
    }

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
            engineListener?.onError(AudioErrorType.ALREADY_ACTIVE, alreadyActiveMessage)
            return false
        }
        if (state == AudioState.ERROR) state = AudioState.IDLE
        return try {
            // Claimed before the hooks so they see the token this start commits; a failed
            // attempt burns a token — harmless, only uniqueness matters.
            session += 1
            if (!openResources()) return false
            if (!initializeAudio()) return false
            state = AudioState.ACTIVE
            startLoop(session)
            engineListener?.onStarted()
            Log.i(tag, startedMessage)
            true
        } catch (e: SecurityException) {
            handleError(AudioErrorType.PERMISSION, "$permissionDeniedMessage: ${e.message}")
            false
        } catch (e: Exception) {
            handleError(AudioErrorType.STREAM, "$startupFailedMessage: ${e.message}")
            false
        }
    }

    /** Subclass: open the session's file/resource; report failures via handleError, return false */
    protected abstract fun openResources(): Boolean

    /** Subclass: build the AudioTrack/AudioRecord; report failures via handleError, return false */
    protected abstract fun initializeAudio(): Boolean

    /** Subclass: launch the run loop on loopScope (assign loopJob); the loop captures [session] as its own token */
    protected abstract fun startLoop(session: Int)

    // Synchronized: start() reads currentConfig piecemeal (openResources → initializeAudio →
    // startLoop); a config swap must not interleave with an in-flight start. Under the engine
    // lock it parks until start commits, then the ACTIVE guard rejects it.
    @Synchronized
    override fun setAudioConfig(config: AudioConfig): AudioConfig {
        if (state == AudioState.ACTIVE) {
            Log.w(tag, "Cannot change configuration while active")
            return currentConfig
        }
        currentConfig = config
        Log.i(tag, "Configuration updated: ${config.description}")
        return currentConfig
    }

    @Synchronized
    override fun stop() = stopIfSession(session)

    /** Lock-held stop body; caller must have verified the state */
    private fun stopLocked() {
        Log.d(tag, "Stopping")
        state = AudioState.IDLE
        loopJob?.cancel()
        releaseAudioResources()
        engineListener?.onStopped()
        Log.i(tag, "Stopped")
    }

    /** Stops only if [expected] is still the current session and the engine is ACTIVE (see [session]) */
    @Synchronized
    protected fun stopIfSession(expected: Int) {
        if (expected != session || state != AudioState.ACTIVE) return
        stopLocked()
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
    protected fun handleError(type: AudioErrorType, detail: String) {
        state = AudioState.ERROR
        Log.e(tag, "Error: $type $detail")
        engineListener?.onError(type, detail)
        releaseAudioResources()
    }

    /**
     * Run-loop failure carrying the loop's own [expected] session token; a stale caller is a
     * no-op (see [session]). Cancellation never false-errors either: stop()/release() set IDLE
     * under the lock before cancelling, so a cancelled loop never sees matching token + ACTIVE.
     */
    @Synchronized
    protected fun handleLoopError(expected: Int, type: AudioErrorType, detail: String) {
        if (expected != session || state != AudioState.ACTIVE) return
        handleError(type, detail)
    }

    companion object {
        /** Run-loop progress logging cadence */
        protected const val PROGRESS_LOG_INTERVAL_BYTES = 5 * 1024 * 1024L
    }

    /** Subclass: release audio resources (stop/handleError paths; always under the engine lock) */
    protected abstract fun releaseAudioResources()
}
